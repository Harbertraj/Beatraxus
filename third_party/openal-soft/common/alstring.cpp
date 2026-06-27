
#include "config.h"

#include "alstring.h"

#include <algorithm>
#include <cctype>
#include <compare>
#include <cstring>
#include <cwctype>


namespace al {

template<typename InputIt1, typename InputIt2, typename Comp>
auto lexicographical_compare_three_way(InputIt1 first1, InputIt1 last1,
    InputIt2 first2, InputIt2 last2, Comp comp) -> std::weak_ordering
{
    for(; (first1 != last1) && (first2 != last2); ++first1, ++first2)
    {
        if(auto c = comp(*first1, *first2); c != 0)
            return c;
    }
    if(first1 == last1)
    {
        if(first2 == last2) return std::weak_ordering::equivalent;
        return std::weak_ordering::less;
    }
    return std::weak_ordering::greater;
}


auto case_compare(const std::string_view str0, const std::string_view str1) noexcept
    -> std::weak_ordering
{
    return al::lexicographical_compare_three_way(str0.cbegin(), str0.cend(),
        str1.cbegin(), str1.cend(), [](const char ch0, const char ch1) -> std::weak_ordering
    {
        using Traits = std::string_view::traits_type;
        const int i0{std::toupper(Traits::to_int_type(ch0))};
        const int i1{std::toupper(Traits::to_int_type(ch1))};
        if(i0 < i1) return std::weak_ordering::less;
        if(i0 > i1) return std::weak_ordering::greater;
        return std::weak_ordering::equivalent;
    });
}

auto case_compare(const std::wstring_view str0, const std::wstring_view str1) noexcept
    -> std::weak_ordering
{
    return al::lexicographical_compare_three_way(str0.cbegin(), str0.cend(),
        str1.cbegin(), str1.cend(), [](const wchar_t ch0, const wchar_t ch1) -> std::weak_ordering
    {
        using Traits = std::wstring_view::traits_type;
        const auto i0{std::towupper(Traits::to_int_type(ch0))};
        const auto i1{std::towupper(Traits::to_int_type(ch1))};
        if(i0 < i1) return std::weak_ordering::less;
        if(i0 > i1) return std::weak_ordering::greater;
        return std::weak_ordering::equivalent;
    });
}

} // namespace al
